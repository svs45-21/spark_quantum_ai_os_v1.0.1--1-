/* Program 9(c) : Insertion Sort Method
   Compiler     : Turbo C++ 3.0                                    */
#include<iostream.h>
#include<conio.h>

void main()
{
  int arr[50], tot, i, j, elem;
  clrscr();
  cout<<"Enter the Size for Array (max. 50): ";
  cin>>tot;
  if(tot>50) tot=50;
  cout<<"Enter "<<tot<<" Array Elements: ";
  for(i=0;i<tot;i++)
    cin>>arr[i];
  for(i=1;i<tot;i++)
  {
    elem=arr[i];
    j=i-1;
    /* shift bigger elements one place to the right */
    while(j>=0 && arr[j]>elem)
    {
      arr[j+1]=arr[j];
      j--;
    }
    arr[j+1]=elem;
  }
  cout<<"\nThe New Array (Sorted Array):\n";
  for(i=0;i<tot;i++)
    cout<<arr[i]<<"  ";
  cout<<endl;
  getch();
}
