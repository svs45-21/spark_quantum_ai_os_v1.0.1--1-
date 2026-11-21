/* Program 8 : Linear Search & Binary Search
   Compiler  : Turbo C++ 3.0                                       */
#include<iostream.h>
#include<conio.h>

void main()
{
  int arr[10], i, num, index, first, last, middle;
  clrscr();
  cout<<"Enter 10 Elements (in ascending order): ";
  for(i=0;i<10;i++)
    cin>>arr[i];
  cout<<"\nEnter a Number to Search: ";
  cin>>num;

  /* ---------- Linear Search ---------- */
  cout<<"\nLinear Search Result is:\n";
  index=-1;
  for(i=0;i<10;i++)
  {
    if(arr[i]==num)
    {
      index=i;
      break;
    }
  }
  if(index!=-1)
    cout<<"Found at Index No. "<<index;
  else
    cout<<"The number, "<<num<<" is not found in given Array";
  cout<<endl;

  /* ---------- Binary Search ---------- */
  cout<<"\nBinary Search Result is:\n";
  first=0;
  last=9;
  middle=(first+last)/2;
  while(first<=last)
  {
    if(arr[middle]<num)
      first=middle+1;
    else if(arr[middle]==num)
    {
      cout<<"The number, "<<num<<" found at Position "<<middle+1;
      break;
    }
    else
      last=middle-1;
    middle=(first+last)/2;
  }
  if(first>last)
    cout<<"The number, "<<num<<" is not found in given Array";
  cout<<endl;
  getch();
}
